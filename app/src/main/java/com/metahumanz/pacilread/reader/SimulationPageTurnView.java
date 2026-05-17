package com.metahumanz.pacilread.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Region;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

public class SimulationPageTurnView extends View {
    public static final int TURN_MODE_SINGLE = 0;
    public static final int TURN_MODE_OUTER_PAGE = 1;
    public static final int TURN_MODE_SPREAD = 2;

    private static final float MIN_TOUCH = 0.1f;

    private final Path path0 = new Path();
    private final Path path1 = new Path();
    private final PointF bezierStart1 = new PointF();
    private final PointF bezierControl1 = new PointF();
    private final PointF bezierVertex1 = new PointF();
    private final PointF bezierEnd1 = new PointF();
    private final PointF bezierStart2 = new PointF();
    private final PointF bezierControl2 = new PointF();
    private final PointF bezierVertex2 = new PointF();
    private final PointF bezierEnd2 = new PointF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foldShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foldHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bookSpinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path desktopPagePath = new Path();
    private final PointF desktopTouchPoint = new PointF();
    private final DesktopFlipCalculation desktopFlipCalculation = new DesktopFlipCalculation();
    private final Matrix matrix = new Matrix();
    private final float[] matrixArray = new float[]{0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f};
    private final ColorMatrixColorFilter backColorFilter = new ColorMatrixColorFilter(
            new ColorMatrix(new float[]{
                    0.9f, 0f, 0f, 0f, 12f,
                    0f, 0.9f, 0f, 0f, 12f,
                    0f, 0f, 0.9f, 0f, 12f,
                    0f, 0f, 0f, 1f, 0f
            })
    );

    private Bitmap frontBitmap;
    private Bitmap backBitmap;
    private int direction = 0;
    private int cornerX = 1;
    private int cornerY = 1;
    private boolean active = false;
    private int turnMode = TURN_MODE_SINGLE;
    private boolean outerPageTurn = false;
    private boolean middleZoneTurn = false;
    private float turnPageLeft = 0f;
    private float turnPageWidth = 0f;
    private float startX = MIN_TOUCH;
    private float startY = MIN_TOUCH;
    private float touchX = MIN_TOUCH;
    private float touchY = MIN_TOUCH;
    private float middleX = 0f;
    private float middleY = 0f;
    private int pageBackgroundColor = 0xFFF7F0E1;

    public SimulationPageTurnView(Context context) {
        this(context, null);
    }

    public SimulationPageTurnView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SimulationPageTurnView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        paint.setStyle(Paint.Style.FILL);
        foldShadowPaint.setStyle(Paint.Style.STROKE);
        foldShadowPaint.setStrokeCap(Paint.Cap.ROUND);
        foldHighlightPaint.setStyle(Paint.Style.STROKE);
        foldHighlightPaint.setStrokeCap(Paint.Cap.ROUND);
        bookSpinePaint.setStyle(Paint.Style.FILL);
        setVisibility(GONE);
    }

    public void setPagingState(
            int direction,
            Bitmap currentBitmap,
            Bitmap incomingBitmap,
            float startX,
            float startY,
            float touchX,
            float touchY,
            int turnMode,
            int pageBackgroundColor
    ) {
        if (direction == 0 || currentBitmap == null || incomingBitmap == null) {
            clear();
            return;
        }
        this.direction = direction;
        this.pageBackgroundColor = pageBackgroundColor;
        this.turnMode = normalizeTurnMode(turnMode);
        this.frontBitmap = currentBitmap;
        this.backBitmap = incomingBitmap;
        this.startY = ensureTouch(startY);
        configureTurnPageBounds(this.turnMode, direction, this.startY);
        this.startX = ensureTouch(toTurnPageX(startX));
        configureCorner();
        updateTouchInternal(toTurnPageX(touchX), touchY);
        active = true;
        setVisibility(VISIBLE);
        invalidate();
    }

    public void clear() {
        active = false;
        direction = 0;
        turnMode = TURN_MODE_SINGLE;
        outerPageTurn = false;
        middleZoneTurn = false;
        turnPageLeft = 0f;
        turnPageWidth = 0f;
        frontBitmap = null;
        backBitmap = null;
        path0.reset();
        path1.reset();
        setVisibility(GONE);
        invalidate();
    }

    public boolean isActive() {
        return active;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active || frontBitmap == null || backBitmap == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (isTurnPageCompletionFrame()) {
            drawCompletedTarget(canvas);
            return;
        }
        if (outerPageTurn) {
            drawOuterPageTurn(canvas);
            return;
        }
        calcPoints();
        buildCurrentFoldPath();
        
        // 1. Draw the area of the NEXT page that is revealed (clipping out the folded part of the current page)
        drawNextPageArea(canvas, backBitmap, 0f);
        
        // 2. Draw the area of the CURRENT page that is still visible
        drawCurrentPageArea(canvas, frontBitmap, 0f);
        
        // 3. Draw the BACK of the turning page (the fold itself)
        drawCurrentBackArea(canvas, direction > 0 ? frontBitmap : backBitmap, 0f);

        // 4. Add a subtle crease/highlight so the mobile curl keeps depth on narrow screens.
        drawFoldDepth(canvas);

    }

    private void drawOuterPageTurn(Canvas canvas) {
        canvas.drawColor(pageBackgroundColor);
        if (isTurnPageCompletionFrame()) {
            drawCompletedTarget(canvas);
            return;
        }
        drawOuterPageFixedHalf(canvas);
        drawOuterPageActiveBaseHalf(canvas);
        drawFixedBookSpine(canvas);
        boolean drewCurl = drawOuterPageCurl(canvas);
        if (drewCurl) {
            drawOuterPageFoldDepth(canvas);
        } else if (isTurnPageCompletionFrame()) {
            drawCompletedTarget(canvas);
        }
    }

    private void configureCorner() {
        calcCornerXY(startX, startY);
        float width = activeWidth();
        float height = stateHeight();
        if (direction > 0 && width / 2f > startX) {
            calcCornerXY(width - startX, startY);
        }
    }

    private void updateTouchInternal(float touchX, float touchY) {
        float adjustedTouchY = touchY;
        float height = stateHeight();
        if (middleZoneTurn) {
            // Match desktop: middle-zone taps/drags use the corner line instead of a free vertical pull.
            float edgeInset = outerPageTurn
                    ? Math.max(getResources().getDisplayMetrics().density, height * 0.018f)
                    : Math.max(1f, getResources().getDisplayMetrics().density);
            adjustedTouchY = startY <= height / 2f ? edgeInset : height - edgeInset;
        }
        this.touchX = ensureTouch(touchX);
        this.touchY = ensureTouch(adjustedTouchY);
    }

    private float ensureTouch(float value) {
        if (value == 0f) {
            return MIN_TOUCH;
        }
        return value;
    }

    private void drawCurrentBackArea(Canvas canvas, Bitmap bitmap, float sourceLeft) {
        path1.reset();
        path1.moveTo(bezierVertex2.x, bezierVertex2.y);
        path1.lineTo(bezierVertex1.x, bezierVertex1.y);
        path1.lineTo(bezierEnd1.x, bezierEnd1.y);
        path1.lineTo(touchX, touchY);
        path1.lineTo(bezierEnd2.x, bezierEnd2.y);
        path1.close();

        canvas.save();
        canvas.clipPath(path0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipPath(path1);
        } else {
            canvas.clipPath(path1, Region.Op.INTERSECT);
        }

        paint.setColorFilter(backColorFilter);
        float distance = (float) Math.hypot(cornerX - bezierControl1.x, bezierControl2.y - cornerY);
        if (distance <= 0f) {
            distance = 1f;
        }
        float f8 = (cornerX - bezierControl1.x) / distance;
        float f9 = (bezierControl2.y - cornerY) / distance;
        matrixArray[0] = 1 - 2 * f9 * f9;
        matrixArray[1] = 2 * f8 * f9;
        matrixArray[3] = matrixArray[1];
        matrixArray[4] = 1 - 2 * f8 * f8;
        matrix.reset();
        matrix.setValues(matrixArray);
        matrix.preTranslate(-bezierControl1.x, -bezierControl1.y);
        matrix.postTranslate(bezierControl1.x, bezierControl1.y);
        canvas.drawColor(pageBackgroundColor);
        if (outerPageTurn) {
            matrix.preTranslate(-sourceLeft, 0f);
        }
        canvas.drawBitmap(bitmap, matrix, paint);
        paint.setColorFilter(null);
        canvas.restore();
    }

    private void drawNextPageArea(Canvas canvas, Bitmap bitmap, float sourceLeft) {
        // Path1 is the total revealed area (from crease to corner)
        path1.reset();
        path1.moveTo(bezierStart1.x, bezierStart1.y);
        path1.lineTo(bezierVertex1.x, bezierVertex1.y);
        path1.lineTo(bezierVertex2.x, bezierVertex2.y);
        path1.lineTo(bezierStart2.x, bezierStart2.y);
        path1.lineTo(cornerX, cornerY);
        path1.close();

        canvas.save();
        clipOuterPageRect(canvas);
        // The total area that is lifted off the flat page is path0.
        // We draw the next page in this entire lifted area.
        // The folded back of the current page will be drawn ON TOP of this later, covering the fold precisely.
        canvas.clipPath(path0);
        
        canvas.drawColor(pageBackgroundColor);
        canvas.drawBitmap(bitmap, bitmapDrawLeft(sourceLeft), 0f, null);
        canvas.restore();
    }

    private void drawCurrentPageArea(Canvas canvas, Bitmap bitmap, float sourceLeft) {
        canvas.save();
        clipOuterPageRect(canvas);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(path0);
        } else {
            canvas.clipPath(path0, Region.Op.XOR);
        }
        canvas.drawColor(pageBackgroundColor);
        canvas.drawBitmap(bitmap, bitmapDrawLeft(sourceLeft), 0f, null);
        canvas.restore();
    }

    private void drawFoldDepth(Canvas canvas) {
        if (outerPageTurn || turnMode == TURN_MODE_SPREAD) {
            return;
        }
        float dx = bezierStart2.x - bezierStart1.x;
        float dy = bezierStart2.y - bezierStart1.y;
        if (Float.isNaN(dx) || Float.isNaN(dy) || Math.hypot(dx, dy) < 1f) {
            return;
        }
        float width = Math.max(getWidth(), 1);
        float height = Math.max(getHeight(), 1);
        float pull = clamp(Math.abs(cornerX - touchX) / width, 0f, 1f);
        float shadowWidth = clamp(Math.min(width, height) * 0.022f, 6f, 24f);
        int shadowAlpha = Math.round(42f + pull * 54f);
        int highlightAlpha = Math.round(18f + pull * 34f);

        canvas.save();
        canvas.clipPath(path0);
        drawCreaseDepthGradient(
                canvas,
                bezierStart1.x,
                bezierStart1.y,
                bezierStart2.x,
                bezierStart2.y,
                shadowWidth,
                shadowAlpha,
                highlightAlpha
        );
        canvas.restore();
    }

    private void calcCornerXY(float x, float y) {
        float width = activeWidth();
        cornerX = Math.round(x <= width / 2f ? 0f : width);
        float height = stateHeight();
        cornerY = y <= height / 2f ? 0 : Math.round(height);
    }

    private void buildCurrentFoldPath() {
        path0.reset();
        path0.moveTo(bezierStart1.x, bezierStart1.y);
        path0.quadTo(bezierControl1.x, bezierControl1.y, bezierEnd1.x, bezierEnd1.y);
        path0.lineTo(touchX, touchY);
        path0.lineTo(bezierEnd2.x, bezierEnd2.y);
        path0.quadTo(bezierControl2.x, bezierControl2.y, bezierStart2.x, bezierStart2.y);
        path0.lineTo(cornerX, cornerY);
        path0.close();
    }

    private void calcPoints() {
        float localTouchX = touchX;
        float localTouchY = touchY;

        middleX = (localTouchX + cornerX) / 2f;
        middleY = (localTouchY + cornerY) / 2f;
        bezierControl1.x = middleX - (cornerY - middleY) * (cornerY - middleY) / safeDivisor(cornerX - middleX);
        bezierControl1.y = cornerY;
        bezierControl2.x = cornerX;
        bezierControl2.y = middleY - (cornerX - middleX) * (cornerX - middleX) / safeDivisor(cornerY - middleY);
        bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2f;
        bezierStart1.y = cornerY;

        float width = activeWidth();
        if (localTouchX > 0 && localTouchX < width) {
            if (bezierStart1.x < 0 || bezierStart1.x > width) {
                if (bezierStart1.x < 0) {
                    bezierStart1.x = width - bezierStart1.x;
                }

                float f1 = Math.abs(cornerX - localTouchX);
                if (f1 <= 0f) {
                    f1 = 1f;
                }
                float f2 = width * f1 / safeDivisor(bezierStart1.x);
                localTouchX = Math.abs(cornerX - f2);
                float f3 = Math.abs(cornerX - localTouchX) * Math.abs(cornerY - localTouchY) / f1;
                localTouchY = Math.abs(cornerY - f3);

                middleX = (localTouchX + cornerX) / 2f;
                middleY = (localTouchY + cornerY) / 2f;
                bezierControl1.x = middleX - (cornerY - middleY) * (cornerY - middleY) / safeDivisor(cornerX - middleX);
                bezierControl1.y = cornerY;
                bezierControl2.x = cornerX;
                bezierControl2.y = middleY - (cornerX - middleX) * (cornerX - middleX) / safeDivisor(cornerY - middleY);
                bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2f;
            }
        }

        touchX = ensureTouch(localTouchX);
        touchY = ensureTouch(localTouchY);
        bezierStart2.x = cornerX;
        bezierStart2.y = bezierControl2.y - (cornerY - bezierControl2.y) / 2f;

        PointF cross1 = getCross(new PointF(touchX, touchY), bezierControl1, bezierStart1, bezierStart2);
        PointF cross2 = getCross(new PointF(touchX, touchY), bezierControl2, bezierStart1, bezierStart2);
        bezierEnd1.set(cross1);
        bezierEnd2.set(cross2);
        bezierVertex1.x = (bezierStart1.x + 2 * bezierControl1.x + bezierEnd1.x) / 4f;
        bezierVertex1.y = (2 * bezierControl1.y + bezierStart1.y + bezierEnd1.y) / 4f;
        bezierVertex2.x = (bezierStart2.x + 2 * bezierControl2.x + bezierEnd2.x) / 4f;
        bezierVertex2.y = (2 * bezierControl2.y + bezierStart2.y + bezierEnd2.y) / 4f;
    }

    private float safeDivisor(float value) {
        if (value == 0f) {
            return 0.1f;
        }
        return value;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void configureTurnPageBounds(int turnMode, int direction, float gestureStartY) {
        float width = stateWidth();
        outerPageTurn = turnMode == TURN_MODE_OUTER_PAGE && width > 0f;
        float height = stateHeight();
        middleZoneTurn = gestureStartY > height / 3f && gestureStartY < height * 2f / 3f;
        if (!outerPageTurn) {
            turnPageLeft = 0f;
            turnPageWidth = width;
            return;
        }
        turnPageWidth = width * 0.5f;
        turnPageLeft = direction > 0 ? turnPageWidth : 0f;
    }

    private float activeWidth() {
        if (outerPageTurn && turnPageWidth > 0f) {
            return turnPageWidth;
        }
        return stateWidth();
    }

    private float stateWidth() {
        int width = getWidth();
        if (width > 0) {
            return width;
        }
        if (frontBitmap != null && !frontBitmap.isRecycled() && frontBitmap.getWidth() > 0) {
            return frontBitmap.getWidth();
        }
        if (backBitmap != null && !backBitmap.isRecycled() && backBitmap.getWidth() > 0) {
            return backBitmap.getWidth();
        }
        return 1f;
    }

    private float stateHeight() {
        int height = getHeight();
        if (height > 0) {
            return height;
        }
        if (frontBitmap != null && !frontBitmap.isRecycled() && frontBitmap.getHeight() > 0) {
            return frontBitmap.getHeight();
        }
        if (backBitmap != null && !backBitmap.isRecycled() && backBitmap.getHeight() > 0) {
            return backBitmap.getHeight();
        }
        return 1f;
    }

    private float toTurnPageX(float x) {
        return outerPageTurn ? x - turnPageLeft : x;
    }

    private float bitmapDrawLeft(float sourceLeft) {
        return outerPageTurn ? -sourceLeft : 0f;
    }

    private void drawFixedBookSpine(Canvas canvas) {
        if (turnMode != TURN_MODE_OUTER_PAGE || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        drawAdaptiveBookSpineLine(canvas);
    }

    private void drawOuterPageFixedHalf(Canvas canvas) {
        if (!outerPageTurn || turnPageWidth <= 0f) {
            canvas.drawBitmap(frontBitmap, 0f, 0f, null);
            return;
        }
        canvas.save();
        if (direction > 0) {
            canvas.clipRect(0f, 0f, turnPageWidth, getHeight());
        } else {
            canvas.clipRect(turnPageWidth, 0f, getWidth(), getHeight());
        }
        canvas.drawBitmap(frontBitmap, 0f, 0f, null);
        canvas.restore();
    }

    private void drawOuterPageBottomHalf(Canvas canvas) {
        if (!outerPageTurn || turnPageWidth <= 0f) {
            return;
        }
        canvas.save();
        if (direction > 0) {
            canvas.clipRect(turnPageWidth, 0f, getWidth(), getHeight());
            canvas.drawBitmap(backBitmap, -turnPageWidth, 0f, null);
        } else {
            canvas.clipRect(0f, 0f, turnPageWidth, getHeight());
            canvas.drawBitmap(backBitmap, 0f, 0f, null);
        }
        canvas.restore();
    }

    private void drawOuterPageActiveBaseHalf(Canvas canvas) {
        if (!outerPageTurn || turnPageWidth <= 0f) {
            return;
        }
        canvas.save();
        if (direction > 0) {
            canvas.clipRect(turnPageWidth, 0f, getWidth(), getHeight());
        } else {
            canvas.clipRect(0f, 0f, turnPageWidth, getHeight());
        }
        canvas.drawBitmap(frontBitmap, 0f, 0f, null);
        canvas.restore();
    }

    private boolean drawOuterPageCurl(Canvas canvas) {
        if (!outerPageTurn || turnPageWidth <= 0f || frontBitmap == null || backBitmap == null) {
            return false;
        }
        int desktopDirection = direction > 0 ? DesktopFlipCalculation.DIRECTION_NEXT : DesktopFlipCalculation.DIRECTION_PREV;
        boolean topCorner = cornerY <= getHeight() / 2f;
        desktopFlipCalculation.configure(
                desktopDirection,
                topCorner,
                turnPageWidth,
                getHeight()
        );
        setOuterDesktopTouchPoint(desktopDirection);
        if (!desktopFlipCalculation.calc(desktopTouchPoint)) {
            return false;
        }
        paint.setFilterBitmap(true);
        paint.setDither(true);
        boolean drewBottom = drawDesktopPage(
                canvas,
                backBitmap,
                desktopDirection == DesktopFlipCalculation.DIRECTION_NEXT ? turnPageWidth : 0f,
                desktopFlipCalculation.getBottomClipArea(),
                desktopFlipCalculation.getBottomPagePosition(),
                0f,
                desktopDirection,
                null
        );
        boolean drewFlip = drawDesktopPage(
                canvas,
                backBitmap,
                desktopDirection == DesktopFlipCalculation.DIRECTION_NEXT ? 0f : turnPageWidth,
                desktopFlipCalculation.getFlippingClipArea(),
                desktopFlipCalculation.getActiveCorner(),
                desktopFlipCalculation.getAngle(),
                desktopDirection,
                backColorFilter
        );
        paint.setColorFilter(null);
        return drewBottom || drewFlip;
    }

    private void drawOuterPageFoldDepth(Canvas canvas) {
        PointF creaseStart = desktopFlipCalculation.getTopIntersectPoint();
        PointF creaseEnd = desktopFlipCalculation.getBottomIntersectPoint();
        PointF sideIntersect = desktopFlipCalculation.getSideIntersectPoint();
        if (creaseStart == null) {
            creaseStart = sideIntersect;
        }
        if (creaseStart == null || creaseEnd == null) {
            creaseEnd = sideIntersect;
        }
        if (creaseStart == null || creaseEnd == null) {
            return;
        }
        int desktopDirection = direction > 0
                ? DesktopFlipCalculation.DIRECTION_NEXT
                : DesktopFlipCalculation.DIRECTION_PREV;
        float startX = outerDesktopToGlobalX(creaseStart.x, desktopDirection);
        float startY = creaseStart.y;
        float endX = outerDesktopToGlobalX(creaseEnd.x, desktopDirection);
        float endY = creaseEnd.y;
        if (!Float.isFinite(startX) || !Float.isFinite(startY)
                || !Float.isFinite(endX) || !Float.isFinite(endY)
                || Math.hypot(endX - startX, endY - startY) < 1f) {
            return;
        }

        float pull = direction > 0
                ? (turnPageWidth - touchX) / (turnPageWidth * 2.1f)
                : touchX / (turnPageWidth * 2.1f);
        pull = clamp(pull, 0f, 1f);
        float depthWidth = clamp(Math.min(turnPageWidth, getHeight()) * 0.035f, 8f, 30f);
        int shadowAlpha = Math.round(34f + pull * 62f);
        int highlightAlpha = Math.round(16f + pull * 34f);

        canvas.save();
        canvas.clipRect(0f, 0f, getWidth(), getHeight());
        drawCreaseDepthGradient(canvas, startX, startY, endX, endY, depthWidth, shadowAlpha, highlightAlpha);
        canvas.restore();
    }

    private void drawCreaseDepthGradient(
            Canvas canvas,
            float startX,
            float startY,
            float endX,
            float endY,
            float shadowWidth,
            int shadowAlpha,
            int highlightAlpha
    ) {
        float dx = endX - startX;
        float dy = endY - startY;
        float length = (float) Math.hypot(dx, dy);
        if (!Float.isFinite(length) || length < 1f) {
            return;
        }
        float normalX = -dy / length;
        float normalY = dx / length;
        float safeShadowWidth = Math.max(1f, shadowWidth);
        drawGradientStroke(
                canvas,
                foldShadowPaint,
                startX,
                startY,
                endX,
                endY,
                normalX,
                normalY,
                safeShadowWidth,
                new int[]{
                        Color.argb(0, 0, 0, 0),
                        Color.argb(shadowAlpha, 0, 0, 0),
                        Color.argb(Math.round(shadowAlpha * 0.42f), 0, 0, 0),
                        Color.argb(0, 0, 0, 0)
                },
                new float[]{0f, 0.42f, 0.62f, 1f}
        );

        float highlightWidth = Math.max(1.5f, safeShadowWidth * 0.24f);
        float highlightOffset = safeShadowWidth * 0.18f;
        drawGradientStroke(
                canvas,
                foldHighlightPaint,
                startX + normalX * highlightOffset,
                startY + normalY * highlightOffset,
                endX + normalX * highlightOffset,
                endY + normalY * highlightOffset,
                normalX,
                normalY,
                highlightWidth,
                new int[]{
                        Color.argb(0, 255, 255, 255),
                        Color.argb(highlightAlpha, 255, 255, 255),
                        Color.argb(0, 255, 255, 255)
                },
                new float[]{0f, 0.5f, 1f}
        );
    }

    private void drawGradientStroke(
            Canvas canvas,
            Paint targetPaint,
            float startX,
            float startY,
            float endX,
            float endY,
            float normalX,
            float normalY,
            float strokeWidth,
            int[] colors,
            float[] positions
    ) {
        float halfWidth = Math.max(0.5f, strokeWidth * 0.5f);
        float midX = (startX + endX) * 0.5f;
        float midY = (startY + endY) * 0.5f;
        Shader shader = new LinearGradient(
                midX - normalX * halfWidth,
                midY - normalY * halfWidth,
                midX + normalX * halfWidth,
                midY + normalY * halfWidth,
                colors,
                positions,
                Shader.TileMode.CLAMP
        );
        targetPaint.setShader(shader);
        targetPaint.setStrokeWidth(strokeWidth);
        targetPaint.setColor(Color.WHITE);
        targetPaint.setAlpha(255);
        canvas.drawLine(startX, startY, endX, endY, targetPaint);
        targetPaint.setShader(null);
        targetPaint.setAlpha(255);
    }

    private void setOuterDesktopTouchPoint(int desktopDirection) {
        if (desktopDirection == DesktopFlipCalculation.DIRECTION_NEXT) {
            desktopTouchPoint.set(touchX, touchY);
            return;
        }
        desktopTouchPoint.set(turnPageWidth - touchX, touchY);
    }

    private boolean drawDesktopPage(
            Canvas canvas,
            Bitmap bitmap,
            float sourceLeft,
            PointF[] area,
            PointF position,
            float angle,
            int desktopDirection,
            ColorMatrixColorFilter colorFilter
    ) {
        if (bitmap == null || area == null || position == null) {
            return false;
        }
        float globalPositionX = outerDesktopToGlobalX(position.x, desktopDirection);
        float globalPositionY = position.y;
        desktopPagePath.reset();
        boolean hasPoint = false;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        for (PointF point : area) {
            if (point == null) {
                continue;
            }
            float localX = desktopDirection == DesktopFlipCalculation.DIRECTION_PREV
                    ? -point.x + position.x
                    : point.x - position.x;
            float localY = point.y - position.y;
            float clipX = localX * cos + localY * sin;
            float clipY = localY * cos - localX * sin;
            if (hasPoint) {
                desktopPagePath.lineTo(clipX, clipY);
            } else {
                desktopPagePath.moveTo(clipX, clipY);
                hasPoint = true;
            }
        }
        if (!hasPoint) {
            return false;
        }
        desktopPagePath.close();

        canvas.save();
        canvas.translate(globalPositionX, globalPositionY);
        canvas.rotate((float) Math.toDegrees(angle));
        canvas.clipPath(desktopPagePath);
        canvas.drawColor(pageBackgroundColor);
        paint.setColorFilter(colorFilter);
        canvas.drawBitmap(bitmap, -sourceLeft, 0f, paint);
        paint.setColorFilter(null);
        canvas.restore();
        return true;
    }

    private boolean isTurnPageCompletionFrame() {
        float width = activeWidth();
        if (direction == 0 || width <= 0f) {
            return false;
        }
        return direction > 0
                ? touchX <= -width * 1.10f
                : touchX >= width * 2.10f;
    }

    private void drawCompletedTarget(Canvas canvas) {
        canvas.drawColor(pageBackgroundColor);
        if (backBitmap != null) {
            canvas.drawBitmap(backBitmap, 0f, 0f, null);
        }
        if (outerPageTurn) {
            drawFixedBookSpine(canvas);
        }
    }

    private float outerDesktopToGlobalX(float x, int desktopDirection) {
        if (desktopDirection == DesktopFlipCalculation.DIRECTION_NEXT) {
            return x + turnPageWidth;
        }
        return turnPageWidth - x;
    }

    private void clipOuterPageRect(Canvas canvas) {
        if (!outerPageTurn || turnPageWidth <= 0f) {
            return;
        }
        canvas.clipRect(0f, 0f, turnPageWidth, getHeight());
    }

    private void drawAdaptiveBookSpineLine(Canvas canvas) {
        float centerX = getWidth() * 0.5f;
        float height = getHeight();
        bookSpinePaint.setShader(null);
        bookSpinePaint.setColor(spineInkColor(lightSpineInk() ? 184 : 132));
        float spineWidth = Math.max(1f, getResources().getDisplayMetrics().density);
        canvas.drawRect(centerX - spineWidth / 2f, 0f, centerX + spineWidth / 2f, height, bookSpinePaint);
    }

    private boolean lightSpineInk() {
        return relativeLuminance(pageBackgroundColor) < 0.45f;
    }

    private int spineInkColor(int alpha) {
        return lightSpineInk()
                ? Color.argb(alpha, 255, 255, 255)
                : Color.argb(alpha, 0, 0, 0);
    }

    private float relativeLuminance(int color) {
        return (float) (
                0.2126 * linearizedChannel(Color.red(color))
                        + 0.7152 * linearizedChannel(Color.green(color))
                        + 0.0722 * linearizedChannel(Color.blue(color))
        );
    }

    private double linearizedChannel(int value) {
        double channel = value / 255.0;
        return channel <= 0.03928
                ? channel / 12.92
                : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private int normalizeTurnMode(int mode) {
        if (mode == TURN_MODE_OUTER_PAGE || mode == TURN_MODE_SPREAD) {
            return mode;
        }
        return TURN_MODE_SINGLE;
    }

    private static final class DesktopFlipCalculation {
        static final int DIRECTION_NEXT = 0;
        static final int DIRECTION_PREV = 1;

        private int direction = DIRECTION_NEXT;
        private boolean topCorner = true;
        private float pageWidth = 1f;
        private float pageHeight = 1f;
        private final PageRect rect = new PageRect();
        private float angle = 0f;
        private final PointF position = new PointF();
        private final PointF safeTouch = new PointF();
        private final PointF firstCenter = new PointF();
        private final PointF secondCenter = new PointF();
        private final PointF pageTopLeft = new PointF();
        private final PointF pageTopRight = new PointF();
        private final PointF pageBottomLeft = new PointF();
        private final PointF pageBottomRight = new PointF();
        private final PointF bottomPagePosition = new PointF();
        private final PointF[] flippingArea = new PointF[]{null, null, null, null, null};
        private final PointF[] bottomArea = new PointF[]{null, null, null, null, null, null};
        private PointF topIntersectPoint;
        private PointF sideIntersectPoint;
        private PointF bottomIntersectPoint;

        void configure(int direction, boolean topCorner, float pageWidth, float pageHeight) {
            this.direction = direction;
            this.topCorner = topCorner;
            this.pageWidth = Math.max(1f, pageWidth);
            this.pageHeight = Math.max(1f, pageHeight);
            pageTopLeft.set(0f, 0f);
            pageTopRight.set(this.pageWidth, 0f);
            pageBottomLeft.set(0f, this.pageHeight);
            pageBottomRight.set(this.pageWidth, this.pageHeight);
        }

        boolean calc(PointF touch) {
            try {
                position.set(calcAngleAndPosition(touch));
                calculateIntersectPoint(position);
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        PointF[] getFlippingClipArea() {
            boolean includeBottomLeft = false;
            clearArea(flippingArea);
            int index = 0;
            flippingArea[index++] = rect.topLeft;
            flippingArea[index++] = topIntersectPoint;
            if (sideIntersectPoint == null) {
                includeBottomLeft = true;
            } else {
                flippingArea[index++] = sideIntersectPoint;
                if (bottomIntersectPoint == null) {
                    includeBottomLeft = false;
                }
            }
            flippingArea[index++] = bottomIntersectPoint;
            if (includeBottomLeft || !topCorner) {
                flippingArea[index] = rect.bottomLeft;
            }
            return flippingArea;
        }

        PointF[] getBottomClipArea() {
            clearArea(bottomArea);
            int index = 0;
            bottomArea[index++] = topIntersectPoint;
            if (topCorner) {
                bottomArea[index++] = pageTopRight;
            } else {
                if (topIntersectPoint != null) {
                    bottomArea[index++] = pageTopRight;
                }
                bottomArea[index++] = pageBottomRight;
            }
            if (sideIntersectPoint != null) {
                if (distance(sideIntersectPoint, topIntersectPoint) >= 10f) {
                    bottomArea[index++] = sideIntersectPoint;
                }
            } else if (topCorner) {
                bottomArea[index++] = pageBottomRight;
            }
            bottomArea[index++] = bottomIntersectPoint;
            bottomArea[index] = topIntersectPoint;
            return bottomArea;
        }

        float getAngle() {
            return direction == DIRECTION_NEXT ? -angle : angle;
        }

        PointF getActiveCorner() {
            return direction == DIRECTION_NEXT ? rect.topLeft : rect.topRight;
        }

        PointF getBottomPagePosition() {
            bottomPagePosition.set(direction == DIRECTION_PREV ? pageWidth : 0f, 0f);
            return bottomPagePosition;
        }

        PointF getTopIntersectPoint() {
            return topIntersectPoint;
        }

        PointF getSideIntersectPoint() {
            return sideIntersectPoint;
        }

        PointF getBottomIntersectPoint() {
            return bottomIntersectPoint;
        }

        private PointF calcAngleAndPosition(PointF touch) {
            safeTouch.set(touch);
            updateAngleAndGeometry(safeTouch);
            if (topCorner) {
                firstCenter.set(0f, 0f);
                secondCenter.set(0f, pageHeight);
            } else {
                firstCenter.set(0f, pageHeight);
                secondCenter.set(0f, 0f);
            }
            PointF result = checkPositionAtCenterLine(safeTouch, firstCenter, secondCenter);
            safeTouch.set(result);
            if (Math.abs(safeTouch.x - pageWidth) < 1f && Math.abs(safeTouch.y) < 1f) {
                throw new IllegalStateException("Point is too small");
            }
            return safeTouch;
        }

        private void updateAngleAndGeometry(PointF point) {
            angle = calculateAngle(point);
            updatePageRect(point);
        }

        private float calculateAngle(PointF point) {
            float x = pageWidth - point.x + 1f;
            float y = topCorner ? point.y : pageHeight - point.y;
            float radius = (float) Math.hypot(y, x);
            if (radius <= 0f) {
                throw new IllegalStateException("Invalid point");
            }
            float value = Math.max(-1f, Math.min(1f, x / radius));
            float result = (float) (2f * Math.acos(value));
            if (y < 0f) {
                result = -result;
            }
            float foldedAngle = (float) Math.PI - result;
            if (!Float.isFinite(result) || (foldedAngle >= 0f && foldedAngle < 0.003f)) {
                throw new IllegalStateException("The G point is too small");
            }
            return topCorner ? result : -result;
        }

        private void updatePageRect(PointF point) {
            if (topCorner) {
                setRotatedPoint(rect.topLeft, 0f, 0f, point);
                setRotatedPoint(rect.topRight, pageWidth, 0f, point);
                setRotatedPoint(rect.bottomLeft, 0f, pageHeight, point);
                setRotatedPoint(rect.bottomRight, pageWidth, pageHeight, point);
            } else {
                setRotatedPoint(rect.topLeft, 0f, -pageHeight, point);
                setRotatedPoint(rect.topRight, pageWidth, -pageHeight, point);
                setRotatedPoint(rect.bottomLeft, 0f, 0f, point);
                setRotatedPoint(rect.bottomRight, pageWidth, 0f, point);
            }
        }

        private void setRotatedPoint(PointF target, float x, float y, PointF origin) {
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            target.set(
                    x * cos + y * sin + origin.x,
                    y * cos - x * sin + origin.y
            );
        }

        private void calculateIntersectPoint(PointF point) {
            if (topCorner) {
                topIntersectPoint = getIntersectBetweenTwoSegment(
                        point,
                        rect.topRight,
                        pageTopLeft,
                        pageTopRight
                );
                sideIntersectPoint = getIntersectBetweenTwoSegment(
                        point,
                        rect.bottomLeft,
                        pageTopRight,
                        pageBottomRight
                );
                bottomIntersectPoint = getIntersectBetweenTwoSegment(
                        rect.bottomLeft,
                        rect.bottomRight,
                        pageBottomLeft,
                        pageBottomRight
                );
            } else {
                topIntersectPoint = getIntersectBetweenTwoSegment(
                        rect.topLeft,
                        rect.topRight,
                        pageTopLeft,
                        pageTopRight
                );
                sideIntersectPoint = getIntersectBetweenTwoSegment(
                        point,
                        rect.topLeft,
                        pageTopRight,
                        pageBottomRight
                );
                bottomIntersectPoint = getIntersectBetweenTwoSegment(
                        rect.bottomLeft,
                        rect.bottomRight,
                        pageBottomLeft,
                        pageBottomRight
                );
            }
        }

        private PointF checkPositionAtCenterLine(PointF point, PointF firstCenter, PointF secondCenter) {
            PointF safePoint = point;
            PointF limited = limitPointToCircle(firstCenter, pageWidth, safePoint);
            if (!samePoint(limited, safePoint)) {
                safePoint = limited;
                updateAngleAndGeometry(safePoint);
            }
            float diagonal = (float) Math.hypot(pageWidth, pageHeight);
            PointF outerCorner = topCorner ? rect.bottomRight : rect.topRight;
            PointF innerCorner = topCorner ? rect.topLeft : rect.bottomLeft;
            if (outerCorner.x <= 0f) {
                limited = limitPointToCircle(secondCenter, diagonal, innerCorner);
                if (!samePoint(limited, safePoint)) {
                    safePoint = limited;
                    updateAngleAndGeometry(safePoint);
                }
            }
            return safePoint;
        }

        private PointF getIntersectBetweenTwoSegment(PointF line1Start, PointF line1End, PointF line2Start, PointF line2End) {
            PointF intersect = getIntersectBetweenTwoLine(line1Start, line1End, line2Start, line2End);
            if (intersect == null) {
                return null;
            }
            return pointInCalculationRect(intersect) ? intersect : null;
        }

        private PointF getIntersectBetweenTwoLine(PointF p1, PointF p2, PointF p3, PointF p4) {
            float a1 = p1.y - p2.y;
            float a2 = p3.y - p4.y;
            float b1 = p2.x - p1.x;
            float b2 = p4.x - p3.x;
            float c1 = p1.x * p2.y - p2.x * p1.y;
            float c2 = p3.x * p4.y - p4.x * p3.y;
            float denominator = a1 * b2 - a2 * b1;
            if (Math.abs(denominator) < 0.0001f) {
                return null;
            }
            return new PointF(
                    -(c1 * b2 - c2 * b1) / denominator,
                    -(a1 * c2 - a2 * c1) / denominator
            );
        }

        private boolean pointInCalculationRect(PointF point) {
            return point.x >= -1f
                    && point.x <= pageWidth + 1f
                    && point.y >= -1f
                    && point.y <= pageHeight + 1f;
        }

        private PointF limitPointToCircle(PointF center, float radius, PointF point) {
            float dx = point.x - center.x;
            float dy = point.y - center.y;
            float distance = (float) Math.hypot(dx, dy);
            if (distance <= radius || distance <= 0.0001f) {
                return point;
            }
            float scale = radius / distance;
            return new PointF(center.x + dx * scale, center.y + dy * scale);
        }

        private boolean samePoint(PointF a, PointF b) {
            return Math.abs(a.x - b.x) < 0.001f && Math.abs(a.y - b.y) < 0.001f;
        }

        private float distance(PointF a, PointF b) {
            if (a == null || b == null) {
                return Float.POSITIVE_INFINITY;
            }
            return (float) Math.hypot(a.x - b.x, a.y - b.y);
        }

        private void clearArea(PointF[] area) {
            for (int i = 0; i < area.length; i++) {
                area[i] = null;
            }
        }
    }

    private static final class PageRect {
        final PointF topLeft = new PointF();
        final PointF topRight = new PointF();
        final PointF bottomLeft = new PointF();
        final PointF bottomRight = new PointF();
    }

    private PointF getCross(PointF p1, PointF p2, PointF p3, PointF p4) {
        float a1 = (p2.y - p1.y) / safeDivisor(p2.x - p1.x);
        float b1 = (p1.x * p2.y - p2.x * p1.y) / safeDivisor(p1.x - p2.x);
        float a2 = (p4.y - p3.y) / safeDivisor(p4.x - p3.x);
        float b2 = (p3.x * p4.y - p4.x * p3.y) / safeDivisor(p3.x - p4.x);
        float x = (b2 - b1) / safeDivisor(a1 - a2);
        float y = a1 * x + b1;
        return new PointF(x, y);
    }
}
