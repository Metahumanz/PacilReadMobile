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
import android.graphics.drawable.GradientDrawable;
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

    private final GradientDrawable backShadowDrawableLr;
    private final GradientDrawable backShadowDrawableRl;
    private final GradientDrawable folderShadowDrawableLr;
    private final GradientDrawable folderShadowDrawableRl;
    private final GradientDrawable frontShadowDrawableHbt;
    private final GradientDrawable frontShadowDrawableHtb;
    private final GradientDrawable frontShadowDrawableVlr;
    private final GradientDrawable frontShadowDrawableVrl;

    private Bitmap frontBitmap;
    private Bitmap backBitmap;
    private int direction = 0;
    private int cornerX = 1;
    private int cornerY = 1;
    private boolean active = false;
    private boolean rtOrLb = false;
    private float startX = MIN_TOUCH;
    private float startY = MIN_TOUCH;
    private float touchX = MIN_TOUCH;
    private float touchY = MIN_TOUCH;
    private float middleX = 0f;
    private float middleY = 0f;
    private float degrees = 0f;
    private float touchToCornerDistance = 0f;
    private float maxLength = 0f;
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

        int[] folderShadowColors = new int[]{0x00333333, 0xB0333333};
        folderShadowDrawableRl = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, folderShadowColors);
        folderShadowDrawableRl.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        folderShadowDrawableLr = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, folderShadowColors);
        folderShadowDrawableLr.setGradientType(GradientDrawable.LINEAR_GRADIENT);

        int[] backShadowColors = new int[]{0x11111111, 0xFF111111};
        backShadowDrawableRl = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, backShadowColors);
        backShadowDrawableRl.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        backShadowDrawableLr = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, backShadowColors);
        backShadowDrawableLr.setGradientType(GradientDrawable.LINEAR_GRADIENT);

        int[] frontShadowColors = new int[]{0x80111111, 0x00111111};
        frontShadowDrawableVlr = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, frontShadowColors);
        frontShadowDrawableVlr.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        frontShadowDrawableVrl = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, frontShadowColors);
        frontShadowDrawableVrl.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        frontShadowDrawableHtb = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, frontShadowColors);
        frontShadowDrawableHtb.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        frontShadowDrawableHbt = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, frontShadowColors);
        frontShadowDrawableHbt.setGradientType(GradientDrawable.LINEAR_GRADIENT);

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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        maxLength = (float) Math.hypot(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active || frontBitmap == null || backBitmap == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        calcPoints();
        
        // 1. Draw the area of the NEXT page that is revealed (clipping out the folded part of the current page)
        drawNextPageAreaAndShadow(canvas, backBitmap);
        
        // 2. Draw the area of the CURRENT page that is still visible
        drawCurrentPageArea(canvas, frontBitmap);
        
        // 3. Draw the BACK of the turning page (the fold itself)
        drawCurrentBackArea(canvas, direction > 0 ? frontBitmap : backBitmap);
        
        // 4. Draw shadows on top
        drawCurrentPageShadow(canvas);
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
        int i = Math.round((bezierStart1.x + bezierControl1.x) / 2f);
        float f1 = Math.abs(i - bezierControl1.x);
        int i1 = Math.round((bezierStart2.y + bezierControl2.y) / 2f);
        float f2 = Math.abs(i1 - bezierControl2.y);
        float shadowWidth = Math.min(f1, f2);
        path1.reset();
        path1.moveTo(bezierVertex2.x, bezierVertex2.y);
        path1.lineTo(bezierVertex1.x, bezierVertex1.y);
        path1.lineTo(bezierEnd1.x, bezierEnd1.y);
        path1.lineTo(touchX, touchY);
        path1.lineTo(bezierEnd2.x, bezierEnd2.y);
        path1.close();

        GradientDrawable folderShadowDrawable;
        int left;
        int right;
        if (rtOrLb) {
            left = Math.round(bezierStart1.x - 1f);
            right = Math.round(bezierStart1.x + shadowWidth + 1f);
            folderShadowDrawable = folderShadowDrawableLr;
        } else {
            left = Math.round(bezierStart1.x - shadowWidth - 1f);
            right = Math.round(bezierStart1.x + 1f);
            folderShadowDrawable = folderShadowDrawableRl;
        }

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
        canvas.rotate(degrees, bezierStart1.x, bezierStart1.y);
        folderShadowDrawable.setBounds(left, Math.round(bezierStart1.y), right, Math.round(bezierStart1.y + maxLength));
        folderShadowDrawable.draw(canvas);
        canvas.restore();
    }

    private void drawCurrentPageShadow(Canvas canvas) {
        double degree;
        if (rtOrLb) {
            degree = Math.PI / 4 - Math.atan2(bezierControl1.y - touchY, touchX - bezierControl1.x);
        } else {
            degree = Math.PI / 4 - Math.atan2(touchY - bezierControl1.y, touchX - bezierControl1.x);
        }
        float d1 = (float) (25f * 1.414f * Math.cos(degree));
        float d2 = (float) (25f * 1.414f * Math.sin(degree));
        float x = touchX + d1;
        float y = rtOrLb ? touchY + d2 : touchY - d2;

        path1.reset();
        path1.moveTo(x, y);
        path1.lineTo(touchX, touchY);
        path1.lineTo(bezierControl1.x, bezierControl1.y);
        path1.lineTo(bezierStart1.x, bezierStart1.y);
        path1.close();
        canvas.save();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(path0);
        } else {
            canvas.clipPath(path0, Region.Op.XOR);
        }
        canvas.clipPath(path1, Region.Op.INTERSECT);

        int leftX;
        int rightX;
        GradientDrawable currentPageShadowDrawable;
        if (rtOrLb) {
            leftX = Math.round(bezierControl1.x);
            rightX = Math.round(bezierControl1.x + 25f);
            currentPageShadowDrawable = frontShadowDrawableVlr;
        } else {
            leftX = Math.round(bezierControl1.x - 25f);
            rightX = Math.round(bezierControl1.x + 1f);
            currentPageShadowDrawable = frontShadowDrawableVrl;
        }

        float rotateDegrees = (float) Math.toDegrees(
                Math.atan2(touchX - bezierControl1.x, bezierControl1.y - touchY)
        );
        canvas.rotate(rotateDegrees, bezierControl1.x, bezierControl1.y);
        currentPageShadowDrawable.setBounds(leftX, Math.round(bezierControl1.y - maxLength), rightX, Math.round(bezierControl1.y));
        currentPageShadowDrawable.draw(canvas);
        canvas.restore();

        path1.reset();
        path1.moveTo(x, y);
        path1.lineTo(touchX, touchY);
        path1.lineTo(bezierControl2.x, bezierControl2.y);
        path1.lineTo(bezierStart2.x, bezierStart2.y);
        path1.close();
        canvas.save();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(path0);
        } else {
            canvas.clipPath(path0, Region.Op.XOR);
        }
        canvas.clipPath(path1);

        if (rtOrLb) {
            leftX = Math.round(bezierControl2.y);
            rightX = Math.round(bezierControl2.y + 25f);
            currentPageShadowDrawable = frontShadowDrawableHtb;
        } else {
            leftX = Math.round(bezierControl2.y - 25f);
            rightX = Math.round(bezierControl2.y + 1f);
            currentPageShadowDrawable = frontShadowDrawableHbt;
        }
        rotateDegrees = (float) Math.toDegrees(
                Math.atan2(bezierControl2.y - touchY, bezierControl2.x - touchX)
        );
        canvas.rotate(rotateDegrees, bezierControl2.x, bezierControl2.y);
        double temp = bezierControl2.y < 0 ? bezierControl2.y - getHeight() : bezierControl2.y;
        double hyp = Math.hypot(bezierControl2.x, temp);
        if (hyp > maxLength) {
            currentPageShadowDrawable.setBounds(
                    (int) (bezierControl2.x - 25f - hyp),
                    leftX,
                    (int) (bezierControl2.x + maxLength - hyp),
                    rightX
            );
        } else {
            currentPageShadowDrawable.setBounds(
                    Math.round(bezierControl2.x - maxLength),
                    leftX,
                    Math.round(bezierControl2.x),
                    rightX
            );
        }
        currentPageShadowDrawable.draw(canvas);
        canvas.restore();
    }

    private void drawNextPageAreaAndShadow(Canvas canvas, Bitmap bitmap) {
        // Path1 is the total revealed area (from crease to corner)
        path1.reset();
        path1.moveTo(bezierStart1.x, bezierStart1.y);
        path1.lineTo(bezierVertex1.x, bezierVertex1.y);
        path1.lineTo(bezierVertex2.x, bezierVertex2.y);
        path1.lineTo(bezierStart2.x, bezierStart2.y);
        path1.lineTo(cornerX, cornerY);
        path1.close();

        degrees = (float) Math.toDegrees(
                Math.atan2(bezierControl1.x - cornerX, bezierControl2.y - cornerY)
        );

        int leftX;
        int rightX;
        GradientDrawable backShadowDrawable;
        if (rtOrLb) {
            leftX = Math.round(bezierStart1.x);
            rightX = Math.round(bezierStart1.x + touchToCornerDistance / 4f);
            backShadowDrawable = backShadowDrawableLr;
        } else {
            leftX = Math.round(bezierStart1.x - touchToCornerDistance / 4f);
            rightX = Math.round(bezierStart1.x);
            backShadowDrawable = backShadowDrawableRl;
        }

        canvas.save();
        // The total area that is lifted off the flat page is path0.
        // We draw the next page in this entire lifted area.
        // The folded back of the current page will be drawn ON TOP of this later, covering the fold precisely.
        canvas.clipPath(path0);
        
        canvas.drawColor(pageBackgroundColor);
        canvas.drawBitmap(bitmap, 0f, 0f, null);
        
        // Draw shadow on the revealed next page
        canvas.rotate(degrees, bezierStart1.x, bezierStart1.y);
        backShadowDrawable.setBounds(leftX, Math.round(bezierStart1.y), rightX, Math.round(maxLength + bezierStart1.y));
        backShadowDrawable.draw(canvas);
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
        rtOrLb = (cornerX == 0 && cornerY == getHeight()) || (cornerY == 0 && cornerX == getWidth());
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
        touchToCornerDistance = (float) Math.hypot(touchX - cornerX, touchY - cornerY);

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
