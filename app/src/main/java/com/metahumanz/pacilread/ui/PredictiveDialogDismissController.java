package com.metahumanz.pacilread.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

public final class PredictiveDialogDismissController {
    private static final float MIN_SCALE = PredictiveBackScaleController.READER_MIN_SCALE;
    private static final float MIN_ALPHA = 0.96f;

    private PredictiveDialogDismissController() {
    }

    public static final class Registration {
        private final AlertDialog dialog;
        private final OnBackInvokedCallback callback;
        private boolean unregistered;

        private Registration(AlertDialog dialog, OnBackInvokedCallback callback) {
            this.dialog = dialog;
            this.callback = callback;
        }

        public void unregister() {
            if (unregistered || dialog == null || callback == null) {
                return;
            }
            unregistered = true;
            unregisterPredictiveDismiss(dialog, callback);
        }
    }

    @SuppressLint("NewApi")
    public static Registration install(
            AlertDialog dialog,
            Window window,
            boolean enabled,
            LaunchSourceTransition.Source dismissSource
    ) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || dialog == null || window == null) {
            return new Registration(null, null);
        }
        View target = window.getDecorView();
        ScreenCornerClipper.apply(target);
        target.post(() -> {
            target.setPivotX(target.getWidth() / 2f);
            target.setPivotY(target.getHeight() / 2f);
        });
        OnBackAnimationCallback callback = new OnBackAnimationCallback() {
            private boolean dismissing;

            @Override
            public void onBackStarted(BackEvent backEvent) {
                if (dismissing) {
                    return;
                }
                target.animate().cancel();
                applyBackProgress(target, backEvent.getProgress());
            }

            @Override
            public void onBackProgressed(BackEvent backEvent) {
                if (dismissing) {
                    return;
                }
                applyBackProgress(target, backEvent.getProgress());
            }

            @Override
            public void onBackCancelled() {
                if (dismissing) {
                    return;
                }
                target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(180L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }

            @Override
            public void onBackInvoked() {
                if (dismissing) {
                    return;
                }
                dismissing = true;
                target.animate().cancel();
                if (LaunchSourceTransition.animateExitToSource(
                        target,
                        dismissSource,
                        230L,
                        () -> dismissIfShowing(dialog)
                )) {
                    return;
                }
                target.animate()
                        .scaleX(MIN_SCALE)
                        .scaleY(MIN_SCALE)
                        .alpha(0f)
                        .setDuration(130L)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> dismissIfShowing(dialog))
                        .start();
            }
        };
        dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
        );
        return new Registration(dialog, callback);
    }

    private static void dismissIfShowing(AlertDialog dialog) {
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    @SuppressLint("NewApi")
    private static void unregisterPredictiveDismiss(AlertDialog dialog, OnBackInvokedCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || dialog == null || callback == null) {
            return;
        }
        try {
            dialog.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void applyBackProgress(View target, float progress) {
        float safeProgress = Math.max(0f, Math.min(1f, progress));
        float eased = 1f - ((1f - safeProgress) * (1f - safeProgress));
        float scale = 1f + (MIN_SCALE - 1f) * eased;
        target.setScaleX(scale);
        target.setScaleY(scale);
        target.setAlpha(1f + (MIN_ALPHA - 1f) * eased);
    }
}
