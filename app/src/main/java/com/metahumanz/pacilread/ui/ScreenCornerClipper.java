package com.metahumanz.pacilread.ui;

import android.graphics.Outline;
import android.os.Build;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;

public final class ScreenCornerClipper {
    private static final float FALLBACK_RADIUS_DP = 32f;

    private ScreenCornerClipper() {
    }

    public static void apply(View target) {
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
                outline.setRoundRect(
                        0,
                        0,
                        view.getWidth(),
                        view.getHeight(),
                        screenCornerRadiusPx(view)
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

    private static float screenCornerRadiusPx(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WindowInsets insets = view.getRootWindowInsets();
            if (insets != null) {
                int radius = 0;
                radius = Math.max(radius, roundedCornerRadius(insets, RoundedCorner.POSITION_TOP_LEFT));
                radius = Math.max(radius, roundedCornerRadius(insets, RoundedCorner.POSITION_TOP_RIGHT));
                radius = Math.max(radius, roundedCornerRadius(insets, RoundedCorner.POSITION_BOTTOM_RIGHT));
                radius = Math.max(radius, roundedCornerRadius(insets, RoundedCorner.POSITION_BOTTOM_LEFT));
                if (radius > 0) {
                    return radius;
                }
            }
        }
        return FALLBACK_RADIUS_DP * view.getResources().getDisplayMetrics().density;
    }

    private static int roundedCornerRadius(WindowInsets insets, int position) {
        RoundedCorner corner = insets.getRoundedCorner(position);
        return corner == null ? 0 : corner.getRadius();
    }
}
