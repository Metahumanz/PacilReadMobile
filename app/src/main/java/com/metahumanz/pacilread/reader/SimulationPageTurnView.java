package com.metahumanz.pacilread.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Region;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

public class SimulationPageTurnView extends View {
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
    private final Matrix matrix = new Matrix();
    private final float[] matrixArray = new float[]{0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f};
    private final ColorMatrixColorFilter backColorFilter = new ColorMatrixColorFilter(
            new ColorMatrix(new float[]{
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
            })
    );

    private Bitmap frontBitmap;
    private Bitmap backBitmap;
    private int direction = 0;
    private int cornerX = 1;
    private int cornerY = 1;
    private boolean active = false;
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
            int pageBackgroundColor
    ) {
        if (direction == 0 || currentBitmap == null || incomingBitmap == null) {
            clear();
            return;
        }
        this.direction = direction;
        this.pageBackgroundColor = pageBackgroundColor;
        this.frontBitmap = currentBitmap;
        this.backBitmap = incomingBitmap;
        this.startX = ensureTouch(startX);
        this.startY = ensureTouch(startY);
        configureCorner();
        updateTouchInternal(touchX, touchY);
        active = true;
        setVisibility(VISIBLE);
        invalidate();
    }

    public void clear() {
        active = false;
        direction = 0;
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
        calcPoints();
        
        // 1. Draw the area of the NEXT page that is revealed (clipping out the folded part of the current page)
        drawNextPageArea(canvas, backBitmap);
        
        // 2. Draw the area of the CURRENT page that is still visible
        drawCurrentPageArea(canvas, frontBitmap);
        
        // 3. Draw the BACK of the turning page (the fold itself)
        drawCurrentBackArea(canvas, direction > 0 ? frontBitmap : backBitmap);
        
    }

    private void configureCorner() {
        calcCornerXY(startX, startY);
        float width = getWidth();
        float height = getHeight();
        if (direction > 0 && width / 2f > startX) {
            calcCornerXY(width - startX, startY);
        }
    }

    private void updateTouchInternal(float touchX, float touchY) {
        float adjustedTouchY = touchY;
        float height = getHeight();
        if (startY > height / 3f && startY < height * 2f / 3f) {
            // Force horizontal turn in the middle zone by aligning touchY with the active corner
            adjustedTouchY = startY <= height / 2f ? MIN_TOUCH : height;
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

    private void drawCurrentBackArea(Canvas canvas, Bitmap bitmap) {
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
        canvas.drawBitmap(bitmap, matrix, paint);
        paint.setColorFilter(null);
        canvas.restore();
    }

    private void drawNextPageArea(Canvas canvas, Bitmap bitmap) {
        // Path1 is the total revealed area (from crease to corner)
        path1.reset();
        path1.moveTo(bezierStart1.x, bezierStart1.y);
        path1.lineTo(bezierVertex1.x, bezierVertex1.y);
        path1.lineTo(bezierVertex2.x, bezierVertex2.y);
        path1.lineTo(bezierStart2.x, bezierStart2.y);
        path1.lineTo(cornerX, cornerY);
        path1.close();

        canvas.save();
        // The total area that is lifted off the flat page is path0.
        // We draw the next page in this entire lifted area.
        // The folded back of the current page will be drawn ON TOP of this later, covering the fold precisely.
        canvas.clipPath(path0);
        
        canvas.drawColor(pageBackgroundColor);
        canvas.drawBitmap(bitmap, 0f, 0f, null);
        canvas.restore();
    }

    private void drawCurrentPageArea(Canvas canvas, Bitmap bitmap) {
        path0.reset();
        path0.moveTo(bezierStart1.x, bezierStart1.y);
        path0.quadTo(bezierControl1.x, bezierControl1.y, bezierEnd1.x, bezierEnd1.y);
        path0.lineTo(touchX, touchY);
        path0.lineTo(bezierEnd2.x, bezierEnd2.y);
        path0.quadTo(bezierControl2.x, bezierControl2.y, bezierStart2.x, bezierStart2.y);
        path0.lineTo(cornerX, cornerY);
        path0.close();

        canvas.save();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(path0);
        } else {
            canvas.clipPath(path0, Region.Op.XOR);
        }
        canvas.drawColor(pageBackgroundColor);
        canvas.drawBitmap(bitmap, 0f, 0f, null);
        canvas.restore();
    }

    private void calcCornerXY(float x, float y) {
        cornerX = x <= getWidth() / 2f ? 0 : getWidth();
        cornerY = y <= getHeight() / 2f ? 0 : getHeight();
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

        if (localTouchX > 0 && localTouchX < getWidth()) {
            if (bezierStart1.x < 0 || bezierStart1.x > getWidth()) {
                // Fix: take absolute value so the crease boundary maps correctly
                if (bezierStart1.x < 0) {
                    bezierStart1.x = Math.abs(bezierStart1.x);
                }

                float f1 = Math.abs(cornerX - localTouchX);
                if (f1 <= 0f) {
                    f1 = 1f;
                }
                float f2 = getWidth() * f1 / safeDivisor(bezierStart1.x);
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
