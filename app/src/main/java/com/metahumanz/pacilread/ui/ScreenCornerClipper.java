package com.metahumanz.pacilread.ui;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Build;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;

public final class ScreenCornerClipper {
    private static final float FALLBACK_RADIUS_DP = 32f;
    private static final float MAX_RADIUS_WINDOW_RATIO = 0.12f;
    private static final float MAX_MULTI_WINDOW_RADIUS_RATIO = 0.08f;

    private ScreenCornerClipper() {
    }

    public static void apply(View target) {
        apply(null, target, null);
    }

    public static void apply(View target, Rect outlineBounds) {
        apply(null, target, outlineBounds);
    }

    public static void apply(Activity activity, View target) {
        apply(activity, target, null);
    }

    public static void apply(Activity activity, View target, Rect outlineBounds) {
        if (target == null) {
            return;
        }
        target.setClipToOutline(true);
        target.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (view.getWidth() <= 0 || view.getHeight() <= 0) {
                    outline.setEmpty();
                    return;
                }
                Rect bounds = outlineBounds == null
                        ? new Rect(0, 0, view.getWidth(), view.getHeight())
                        : new Rect(outlineBounds);
                if (!bounds.intersect(0, 0, view.getWidth(), view.getHeight()) || bounds.isEmpty()) {
                    outline.setEmpty();
                    return;
                }
                outline.setRoundRect(
                        bounds.left,
                        bounds.top,
                        bounds.right,
                        bounds.bottom,
                        screenCornerRadiusPx(activity, view)
                );
            }
        });
        target.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                view.invalidateOutline();
            }
        });
        target.post(target::invalidateOutline);
    }

    private static float screenCornerRadiusPx(Activity activity, View view) {
        if (isInMultiWindowMode(activity)) {
            return adaptiveWindowCornerRadiusPx(view);
        }
        float radius;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WindowInsets insets = view.getRootWindowInsets();
            if (insets != null) {
                int detectedRadius = 0;
                detectedRadius = Math.max(detectedRadius, roundedCornerRadius(insets, RoundedCorner.POSITION_TOP_LEFT));
                detectedRadius = Math.max(detectedRadius, roundedCornerRadius(insets, RoundedCorner.POSITION_TOP_RIGHT));
                detectedRadius = Math.max(detectedRadius, roundedCornerRadius(insets, RoundedCorner.POSITION_BOTTOM_RIGHT));
                detectedRadius = Math.max(detectedRadius, roundedCornerRadius(insets, RoundedCorner.POSITION_BOTTOM_LEFT));
                if (detectedRadius > 0) {
                    radius = detectedRadius;
                    return clampRadiusToWindow(view, radius);
                }
            }
        }
        radius = FALLBACK_RADIUS_DP * view.getResources().getDisplayMetrics().density;
        return clampRadiusToWindow(view, radius);
    }

    private static float adaptiveWindowCornerRadiusPx(View view) {
        float radius = FALLBACK_RADIUS_DP * view.getResources().getDisplayMetrics().density;
        return clampRadiusToWindow(view, radius, MAX_MULTI_WINDOW_RADIUS_RATIO);
    }

    private static float clampRadiusToWindow(View view, float radius) {
        return clampRadiusToWindow(view, radius, MAX_RADIUS_WINDOW_RATIO);
    }

    private static float clampRadiusToWindow(View view, float radius, float maxRatio) {
        int width = view.getWidth();
        int height = view.getHeight();
        int shortestSide = Math.min(width, height);
        if (shortestSide <= 0) {
            return radius;
        }
        float windowMax = shortestSide * maxRatio;
        return Math.max(0f, Math.min(radius, windowMax));
    }

    private static boolean isInMultiWindowMode(Activity activity) {
        return activity != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && activity.isInMultiWindowMode();
    }

    @TargetApi(Build.VERSION_CODES.S)
    private static int roundedCornerRadius(WindowInsets insets, int position) {
        RoundedCorner corner = insets.getRoundedCorner(position);
        return corner == null ? 0 : corner.getRadius();
    }
}
