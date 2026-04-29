package com.metahumanz.pacilread.ui;

import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public final class LaunchSourceTransition {
    private static final String EXTRA_LEFT = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_LEFT";
    private static final String EXTRA_TOP = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_TOP";
    private static final String EXTRA_RIGHT = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_RIGHT";
    private static final String EXTRA_BOTTOM = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_BOTTOM";
    private static final String LEGACY_EXTRA_LEFT = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_LEFT";
    private static final String LEGACY_EXTRA_TOP = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_TOP";
    private static final String LEGACY_EXTRA_RIGHT = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_RIGHT";
    private static final String LEGACY_EXTRA_BOTTOM = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_BOTTOM";

    private LaunchSourceTransition() {
    }

    public static void attach(Intent intent, View sourceView) {
        if (intent == null || sourceView == null || sourceView.getWidth() <= 0 || sourceView.getHeight() <= 0) {
            return;
        }
        Rect bounds = new Rect();
        if (!sourceView.getGlobalVisibleRect(bounds) || bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        intent.putExtra(EXTRA_LEFT, bounds.left);
        intent.putExtra(EXTRA_TOP, bounds.top);
        intent.putExtra(EXTRA_RIGHT, bounds.right);
        intent.putExtra(EXTRA_BOTTOM, bounds.bottom);
    }

    public static Rect fromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String leftKey = intent.hasExtra(EXTRA_LEFT) ? EXTRA_LEFT : LEGACY_EXTRA_LEFT;
        String topKey = intent.hasExtra(EXTRA_LEFT) ? EXTRA_TOP : LEGACY_EXTRA_TOP;
        String rightKey = intent.hasExtra(EXTRA_LEFT) ? EXTRA_RIGHT : LEGACY_EXTRA_RIGHT;
        String bottomKey = intent.hasExtra(EXTRA_LEFT) ? EXTRA_BOTTOM : LEGACY_EXTRA_BOTTOM;
        if (!intent.hasExtra(leftKey)) {
            return null;
        }
        Rect bounds = new Rect(
                intent.getIntExtra(leftKey, 0),
                intent.getIntExtra(topKey, 0),
                intent.getIntExtra(rightKey, 0),
                intent.getIntExtra(bottomKey, 0)
        );
        return bounds.width() > 0 && bounds.height() > 0 ? bounds : null;
    }

    public static boolean animateExitToSource(
            View targetView,
            Rect targetBounds,
            long durationMs,
            Runnable onComplete
    ) {
        if (targetView == null || targetBounds == null || targetBounds.width() <= 0 || targetBounds.height() <= 0) {
            return false;
        }
        if (targetView.getWidth() <= 0 || targetView.getHeight() <= 0) {
            return false;
        }
        ScreenCornerClipper.apply(targetView);
        int[] targetLocation = new int[2];
        targetView.getLocationOnScreen(targetLocation);
        float destinationCenterX = targetBounds.centerX() - targetLocation[0];
        float destinationCenterY = targetBounds.centerY() - targetLocation[1];
        float targetCenterX = targetView.getWidth() / 2f;
        float targetCenterY = targetView.getHeight() / 2f;
        float destinationScaleX = clampScale(targetBounds.width() / (float) targetView.getWidth());
        float destinationScaleY = clampScale(targetBounds.height() / (float) targetView.getHeight());

        targetView.animate().cancel();
        targetView.animate()
                .scaleX(destinationScaleX)
                .scaleY(destinationScaleY)
                .translationX(destinationCenterX - targetCenterX)
                .translationY(destinationCenterY - targetCenterY)
                .alpha(0f)
                .setDuration(durationMs)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(onComplete)
                .start();
        return true;
    }

    private static float clampScale(float scale) {
        return Math.max(0.08f, Math.min(1f, scale));
    }
}
